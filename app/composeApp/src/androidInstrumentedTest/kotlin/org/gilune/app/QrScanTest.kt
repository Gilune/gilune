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
import kotlin.test.assertNull

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
}
