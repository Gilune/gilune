// The microphone foreground service, exercised on a device (ADR 0017
// § 4). Two claims, and they are the two that matter:
//
//   1. A start that names the widget records, and stopping it lands a
//      capture in the vault — streamed, so what lands is a canonical WAV.
//   2. A start that names nothing is refused and never opens the mic.
//
// This test writes into the app's real vault, because the service's whole
// point is that it opens the vault itself rather than borrowing an
// activity's handle. On a throwaway emulator that is the honest thing to
// exercise; it is not a test to run against a vault you care about.

package org.gilune.app

import android.Manifest
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordServiceTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grantWhatARealTapWouldHaveGranted() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val package_ = context.packageName
        runCatching { automation.grantRuntimePermission(package_, Manifest.permission.RECORD_AUDIO) }
        runCatching { automation.grantRuntimePermission(package_, Manifest.permission.POST_NOTIFICATIONS) }
    }

    private fun recordings(): List<File> {
        val captures = File(vaultDir(context), "Captures")
        return captures.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }.orEmpty()
    }

    /// The `data` chunk's declared size, at its fixed offset in the
    /// canonical 44-byte header.
    private fun wavStreamHeaderSizeField(bytes: ByteArray): Int =
        (bytes[40].toInt() and 0xFF) or
            ((bytes[41].toInt() and 0xFF) shl 8) or
            ((bytes[42].toInt() and 0xFF) shl 16) or
            ((bytes[43].toInt() and 0xFF) shl 24)

    private fun waitFor(what: String, timeoutMs: Long = 20_000, until: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (until()) return
            Thread.sleep(100)
        }
        throw AssertionError("timed out waiting for $what")
    }

    @Test
    fun aWidgetRecordingLandsAsACaptureWithoutTheAppEverBeingComposed() {
        val before = recordings().map { it.name }.toSet()

        context.startForegroundService(RecordService.startIntent(context, SOURCE_WIDGET))
        waitFor("the microphone to open") { RecordService.running }

        // Long enough to cross a chunk boundary. At the canonical rate
        // CAPTURE_CHUNK is a little over two seconds, so three seconds
        // means at least one whole block was handed to the kernel *while
        // the microphone was still open* — which is the claim the widget
        // makes: a kill loses only the tail.
        Thread.sleep(3_000)

        context.startForegroundService(RecordService.stopIntent(context, SOURCE_WIDGET))
        waitFor("the recording to stop") { !RecordService.running }
        waitFor("the capture to land") { recordings().any { it.name !in before } }

        val landed = recordings().first { it.name !in before }
        val bytes = landed.readBytes()
        assertTrue(
            bytes.size > 44 + CAPTURE_CHUNK,
            "a full chunk must have crossed before the stop: ${bytes.size} bytes",
        )
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        // The header a stream opens with says its length is unknown, and
        // the duration is read off the file instead.
        assertEquals(WAV_SIZE_UNKNOWN, wavStreamHeaderSizeField(bytes), "the data chunk declares unknown")
        assertTrue(wavDurationMs(bytes, landed.length())!! > 2_000, "about three seconds of audio")
        // The canonical shape, in the offsets the kernel's transcriber
        // reads: 1 channel, 16 kHz, 16-bit.
        assertEquals(1, bytes[22].toInt())
        assertEquals(0x3E80, (bytes[25].toInt() and 0xFF) shl 8 or (bytes[24].toInt() and 0xFF))
        assertEquals(16, bytes[34].toInt())
        // And the note it made is projected beside it.
        waitFor("the capture note to be projected") {
            File(vaultDir(context), "Captures").listFiles()
                ?.any { it.name == landed.name.removeSuffix(".wav") + ".md" } == true
        }
    }

    @Test
    fun aStartThatNamesNoSourceIsRefusedAndNeverOpensTheMicrophone() {
        // § 4's refusal, mechanically: there is no third source, so
        // anything background-initiated cannot construct a legal start.
        val nameless = RecordService.startIntent(context, SOURCE_WIDGET)
            .apply { removeExtra(EXTRA_SOURCE) }
        context.startForegroundService(nameless)
        Thread.sleep(2_000)
        assertFalse(RecordService.running, "a start with no source must not record")
    }
}
