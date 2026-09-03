// Scanning a pairing QR (ADR 0019 § 7).
//
// The desktop encodes the matrix in Rust and paints it; the phone points a
// camera at it. Two rules shape everything below:
//
//  - **CameraX plus `com.google.zxing:core`, decoding the analysis frame
//    directly.** No wrapper library, and deliberately not ML Kit's code
//    scanner, which would need no permission at all but is closed source
//    and hard-requires Play services — declined in the ADR's alternatives
//    for an app that removed OkHttp from a vendor AAR to keep a network
//    fetch inside its consent gate.
//  - **The camera is a convenience, never a condition.** The permission is
//    asked for at the moment Scan is tapped, and the six-digit path sits
//    beside the button on the same screen, so a refusal costs a person
//    nothing but two more taps.
//
// No frame is stored and none leaves this process: the analyser reads the
// luminance plane, asks ZXing, and closes the image.

package org.gilune.app

import android.graphics.ImageFormat
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/// How tall the viewfinder is. Big enough to aim with, small enough that
/// the six-digit fields stay on the same screen underneath it — which is
/// the ADR's requirement rendered as a layout decision.
private val VIEWFINDER = 260.dp

/// The decode, as a pure function over one luminance plane.
///
/// Held apart from the analyser deliberately: everything CameraX-shaped is
/// in the caller, and what is left is bytes in, payload out. ZXing's QR
/// detector finds its three finder patterns at any orientation, so the
/// frame's rotation is never corrected here — a phone held sideways reads
/// the same code.
///
/// `stride` is the row stride the camera reported, which is **not**
/// necessarily the width: a short plane is padded rather than trusted, so
/// a driver that hands back fewer bytes than its own geometry claims
/// cannot walk off the end of the array.
fun decodeQrLuminance(
    reader: MultiFormatReader,
    plane: ByteArray,
    stride: Int,
    width: Int,
    height: Int,
): String? {
    if (width <= 0 || height <= 0 || stride < width) return null
    val needed = stride * height
    val data = if (plane.size >= needed) plane else plane.copyOf(needed)
    val source = PlanarYUVLuminanceSource(data, stride, height, 0, 0, width, height, false)
    return try {
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))?.text
    } catch (e: Throwable) {
        // Not-found is the overwhelmingly common answer — most frames hold
        // no QR at all — and a checksum or format failure is the same
        // outcome for a person: keep looking.
        null
    } finally {
        reader.reset()
    }
}

/// A reader restricted to QR. Narrowing the formats is what keeps a
/// per-frame decode cheap enough to run on every frame.
fun qrReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
}

/// One payload, once. The analyser keeps running until the composable
/// leaves, so the latch is what stops a code held in front of the lens
/// from pairing four times a second.
private class QrAnalyzer(private val onPayload: (String) -> Unit) : ImageAnalysis.Analyzer {
    private val reader = qrReader()
    private val spent = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (spent.get()) return
            if (image.format != ImageFormat.YUV_420_888) return
            val plane = image.planes.firstOrNull() ?: return
            val buffer = plane.buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            val payload = decodeQrLuminance(reader, bytes, plane.rowStride, image.width, image.height)
            if (payload != null && spent.compareAndSet(false, true)) onPayload(payload)
        } catch (e: Throwable) {
            // A frame that cannot be read is a frame; the next one arrives
            // in milliseconds. Nothing here may end a scan.
        } finally {
            image.close()
        }
    }
}

/// The viewfinder. Binds to the composition's lifecycle, so leaving the
/// screen — or the phone going to the launcher — releases the camera
/// without anything here remembering to.
@Composable
fun QrScanner(
    onPayload: (String) -> Unit,
    onFailure: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // **COMPATIBLE, not the default PERFORMANCE**, and this is a defect
    // fix rather than a preference (seen on the emulator, 2026-09-03):
    // PERFORMANCE draws into a `SurfaceView`, which is its own window
    // punched through the hierarchy — it ignores the scrolling settings
    // column's clipping and painted the camera over the six-digit fields
    // underneath it. COMPATIBLE uses a `TextureView`, which composites in
    // the view tree, so the viewfinder stays inside its own box and
    // scrolls with the page. The cost is a copy per frame, which for a
    // viewfinder a person holds up for a few seconds is nothing.
    val preview = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(lifecycleOwner) {
        var bound: ProcessCameraProvider? = null
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    val provider = future.get()
                    bound = provider
                    val viewfinder = Preview.Builder().build()
                    viewfinder.setSurfaceProvider(preview.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        // Decode what is in front of the lens now, not a
                        // backlog of what was.
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        executor,
                        QrAnalyzer { payload -> preview.post { onPayload(payload) } },
                    )
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        viewfinder,
                        analysis,
                    )
                } catch (e: Throwable) {
                    onFailure(e.message ?: "the camera could not be opened")
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { bound?.unbindAll() }
            executor.shutdown()
        }
    }

    Box(modifier.fillMaxWidth().height(VIEWFINDER).testTag("qr-scanner")) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().height(VIEWFINDER))
    }
}
