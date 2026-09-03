// The pairing QR's decode (ADR 0019 § 7), which M5b shipped untested.
//
// `decodeQrLuminance` is held apart from CameraX precisely so it can be
// exercised like this: bytes in, payload out. The case worth the test is
// the row stride — a camera plane is padded to its own stride and is
// **not** necessarily the width, and a driver that hands back fewer bytes
// than its geometry claims must not walk off the end of the array.

package org.gilune.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class QrScanTest {

    /// One luminance plane holding `text`, padded to `stride`. Black
    /// modules are 0 and everything else is white, which is the shape a
    /// camera's Y plane arrives in.
    private fun plane(text: String, stride: Int = 0): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 240, 240)
        val width = matrix.width
        val height = matrix.height
        val rowStride = if (stride > 0) stride else width
        val bytes = ByteArray(rowStride * height) { -1 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix.get(x, y)) bytes[y * rowStride + x] = 0
            }
        }
        return Triple(bytes, rowStride, width)
    }

    @Test
    fun aFrameHoldingAPairingPayloadDecodesToIt() {
        val payload = "gilune-sync/1|192.168.1.20:51837|ABCDEFGH|" + "0".repeat(32)
        val (bytes, stride, width) = plane(payload)
        val height = bytes.size / stride
        assertEquals(payload, decodeQrLuminance(qrReader(), bytes, stride, width, height))
    }

    /// A padded plane is the ordinary case on real hardware, and the
    /// pixels are found by stride rather than by width.
    @Test
    fun aPaddedRowStrideIsHonoured() {
        val payload = "gilune-sync/1|10.0.0.4:51837"
        val (bytes, stride, width) = plane(payload, stride = 320)
        val height = bytes.size / stride
        assertEquals(payload, decodeQrLuminance(qrReader(), bytes, stride, width, height))
    }

    @Test
    fun aFrameWithNoCodeInItIsSimplyNoAnswer() {
        val blank = ByteArray(240 * 240) { -1 }
        assertNull(decodeQrLuminance(qrReader(), blank, 240, 240, 240))
    }

    /// Nonsense geometry answers null rather than reaching the decoder.
    /// A stride smaller than the width is the one that would index past
    /// the end of the plane.
    @Test
    fun impossibleGeometryIsRefusedRatherThanIndexed() {
        val bytes = ByteArray(240 * 240)
        assertNull(decodeQrLuminance(qrReader(), bytes, 240, 0, 240))
        assertNull(decodeQrLuminance(qrReader(), bytes, 240, 240, 0))
        assertNull(decodeQrLuminance(qrReader(), bytes, 100, 240, 240))
    }

    /// A driver that reports geometry it did not fill. The plane is padded
    /// rather than trusted, so this is a miss and never a crash.
    @Test
    fun aShortPlaneIsPaddedRatherThanTrusted() {
        assertNull(decodeQrLuminance(qrReader(), ByteArray(16), 240, 240, 240))
    }

    // ------------------------------------------------------------------
    // The analyser's one buffer
    //
    // Analysis frames arrive many times a second and a Y plane is the
    // frame's whole area, so a `ByteArray` per frame was megabytes a second
    // of garbage on a screen whose whole job is to wait. The buffer is
    // reused; these say what "reused" must mean.
    // ------------------------------------------------------------------

    @Test
    fun theScanBuysOneBufferAndKeepsIt() {
        val scratch = LuminanceScratch()
        val frame = java.nio.ByteBuffer.wrap(ByteArray(240 * 240) { -1 })
        val first = scratch.take(frame, 240 * 240)
        frame.rewind()
        val second = scratch.take(frame, 240 * 240)
        assertSame(first, second, "a second frame of the same shape allocated again")
        assertEquals(1, scratch.allocations)
    }

    @Test
    fun aBiggerFrameGrowsTheBufferAndASmallerOneDoesNot() {
        val scratch = LuminanceScratch()
        scratch.take(java.nio.ByteBuffer.wrap(ByteArray(100)), 100)
        assertEquals(1, scratch.allocations)
        scratch.take(java.nio.ByteBuffer.wrap(ByteArray(400)), 400)
        assertEquals(2, scratch.allocations)
        assertEquals(400, scratch.capacity)
        // A smaller frame afterwards keeps the larger buffer: geometry does
        // not change mid-scan, and re-allocating downwards would be the
        // per-frame allocation coming back the other way.
        scratch.take(java.nio.ByteBuffer.wrap(ByteArray(100)), 100)
        assertEquals(2, scratch.allocations)
        assertEquals(400, scratch.capacity)
    }

    @Test
    fun aShortPlanesTailIsZeroedRatherThanTheLastFramesPixels() {
        // The reused-buffer hazard, and the reason the tail is filled: a
        // driver that reports geometry it did not fill would otherwise be
        // decoding the previous frame's pixels at the bottom of this one.
        val scratch = LuminanceScratch()
        scratch.take(java.nio.ByteBuffer.wrap(ByteArray(64) { -1 }), 64)
        val short = scratch.take(java.nio.ByteBuffer.wrap(ByteArray(8) { 7 }), 64)
        assertEquals(7.toByte(), short[7])
        for (index in 8 until 64) {
            assertEquals(
                0.toByte(),
                short[index],
                "byte $index is the last frame's, not this one's",
            )
        }
    }

    @Test
    fun aReusedBufferStillDecodesTheCodeItIsHolding() {
        // End to end through the same call the analyser makes: the payload
        // survives the buffer being one somebody else already used.
        val payload = "gilune-sync/1|192.168.1.20:51837|ABCDEFGH"
        val (bytes, stride, width) = plane(payload)
        val height = bytes.size / stride
        val scratch = LuminanceScratch()
        scratch.take(java.nio.ByteBuffer.wrap(ByteArray(bytes.size) { -1 }), bytes.size)
        val reused = scratch.take(java.nio.ByteBuffer.wrap(bytes), stride * height)
        assertEquals(payload, decodeQrLuminance(qrReader(), reused, stride, width, height))
        assertEquals(1, scratch.allocations, "the second frame allocated")
    }

    // ------------------------------------------------------------------
    // The scan's lifetime
    //
    // **The defect these pin.** `ProcessCameraProvider.getInstance` is a
    // future whose listener runs whenever the provider is ready — which
    // can be after the composable has left. The shipped code then bound a
    // camera to a dead composition and handed `setAnalyzer` an executor
    // `onDispose` had already shut down: a `RejectedExecutionException`
    // out of CameraX's own thread, on a screen the person had walked away
    // from. `QrSession` is that race made into a question, so it can be
    // asked without a camera.
    // ------------------------------------------------------------------

    @Test
    fun aLiveSessionOffersItsExecutorAndSaysTheCameraIsStillWanted() {
        val session = QrSession()
        assertTrue(session.wanted())
        assertNotNull(session.analyserExecutor())
        assertFalse(session.isShutDown())
    }

    @Test
    fun aDisposedSessionRefusesTheCameraAndWithholdsTheExecutorEntirely() {
        val session = QrSession()
        session.dispose()
        assertFalse(session.wanted(), "a provider arriving now must not bind")
        // Withheld rather than merely shut down: `setAnalyzer` on a
        // shut-down executor is what threw, and null makes it
        // unexpressible instead of avoided by care.
        assertNull(session.analyserExecutor())
        assertTrue(session.isShutDown())
    }

    @Test
    fun disposingTwiceIsDisposingOnce() {
        // `onDispose` runs on a change of lifecycle owner as well as on
        // leaving, and a second shutdown must not be a second event.
        val session = QrSession()
        session.dispose()
        session.dispose()
        assertFalse(session.wanted())
        assertTrue(session.isShutDown())
    }

    @Test
    fun theExecutorIsTheSameOneForAsLongAsTheSessionLives() {
        // The analyser is handed this once; a session that minted a new
        // executor per call would leak a thread per frame-rate decision.
        val session = QrSession()
        assertSame(session.analyserExecutor(), session.analyserExecutor())
    }
}
