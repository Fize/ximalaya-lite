package com.shixi.client

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One reader per analyzer executor. No barcode models, native runtime or format dispatcher. */
internal class WebLoginQrDecoder {
    private val reader = QRCodeReader()
    fun decode(source: LuminanceSource): String? = try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
    } catch (_: ReaderException) { null } finally { reader.reset() }

    fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        return decode(luminance(image.width, image.height, plane.rowStride, plane.pixelStride, plane.buffer, image.cropRect))
    }

    companion object {
        /** ImageProxy Y rows can have padding; only copy pixels inside its crop rectangle. */
        fun luminance(width: Int, height: Int, rowStride: Int, pixelStride: Int, buffer: ByteBuffer,
            crop: android.graphics.Rect): LuminanceSource {
            require(width > 0 && height > 0 && rowStride > 0 && pixelStride > 0 &&
                crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height && !crop.isEmpty)
            val input = buffer.duplicate()
            val base = input.position()
            val output = ByteArray(crop.width() * crop.height())
            for (y in 0 until crop.height()) {
                val offset = base + (crop.top + y) * rowStride + crop.left * pixelStride
                for (x in 0 until crop.width()) output[y * crop.width() + x] = input.get(offset + x * pixelStride)
            }
            return PlanarYUVLuminanceSource(output, crop.width(), crop.height(), 0, 0, crop.width(), crop.height(), false)
        }
    }
}

@Composable
internal fun WebLoginCamera(modifier: Modifier, onCode: (String) -> Unit, onFailure: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = androidx.compose.runtime.remember { PreviewView(context) }
    AndroidView(factory = { view }, modifier = modifier)
    DisposableEffect(owner) {
        val scanner = WebLoginQrDecoder()
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val disposed = AtomicBoolean(false)
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(android.util.Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { proxy ->
            try {
                if (!disposed.get()) scanner.decode(proxy)?.let { code ->
                    main.execute { if (!disposed.get()) onCode(code) }
                }
            } catch (_: Exception) {
                main.execute { if (!disposed.get()) onFailure() }
            } finally { proxy.close() }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (!disposed.get()) try {
                provider = future.get()
                provider!!.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (_: Exception) { if (!disposed.get()) onFailure() }
        }, main)
        onDispose {
            disposed.set(true)
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            executor.shutdown()
        }
    }
}

