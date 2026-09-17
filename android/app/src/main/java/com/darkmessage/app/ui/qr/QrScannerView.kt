package com.darkmessage.app.ui.qr

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Live QR scanner built on CameraX + ZXing core (spec 3.4 / 5.2).
 *
 * No ML Kit and no Google Play services: the app must work on devices without GMS (RuStore).
 * The camera is released as soon as the first code is accepted, so nothing keeps running behind
 * the PIN step, and frames are never stored or logged.
 *
 * @param onCode invoked once per accepted code, on the main thread, with the decoded text.
 * @param torchOn drives the torch; ignored on devices without a flash unit.
 * @param restartKey re-arms the scanner when its value changes: the camera is rebound and the
 *   next code is accepted again. Needed when a scanned code is rejected (not Dark Message /
 *   wrong version / malformed) and scanning has to resume, or after the PIN-attempt lockout.
 *   The 2 s identical-string debounce of [ZxingQrAnalyzer] survives the restart, so the same
 *   rejected code does not immediately re-trigger.
 * @param onTorchAvailable reports whether the bound camera has a flash unit, so the caller can
 *   hide the torch toggle (spec 3.3: shown only when the camera has a torch).
 */
@Composable
fun QrScannerView(
    onCode: (String) -> Unit,
    torchOn: Boolean = false,
    modifier: Modifier = Modifier,
    restartKey: Any? = null,
    onTorchAvailable: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val accepted = remember { AtomicBoolean(false) }

    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var imageAnalysis by remember { mutableStateOf<ImageAnalysis?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    // One analyzer for the whole lifetime of the scanner, so its debounce history is not lost
    // when the camera is rebound. Its callback already runs on the main thread.
    val analyzer = remember {
        ZxingQrAnalyzer { text ->
            // First accepted code wins: stop the camera before handing the text over.
            if (accepted.compareAndSet(false, true)) {
                imageAnalysis?.clearAnalyzer()
                cameraProvider?.unbindAll()
                currentOnCode(text)
            }
        }
    }

    LaunchedEffect(lifecycleOwner, previewView, restartKey) {
        imageAnalysis?.clearAnalyzer()
        accepted.set(false)
        val provider = try {
            awaitCameraProvider(context)
        } catch (_: Throwable) {
            currentOnTorchAvailable(false)
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysis.setAnalyzer(analysisExecutor, analyzer)
        try {
            provider.unbindAll()
            val bound = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            cameraProvider = provider
            imageAnalysis = analysis
            camera = bound
            currentOnTorchAvailable(bound.cameraInfo.hasFlashUnit())
        } catch (_: Throwable) {
            // No back camera, camera held by another app, or the device refused the config.
            analysis.clearAnalyzer()
            provider.unbindAll()
            cameraProvider = provider
            imageAnalysis = null
            camera = null
            currentOnTorchAvailable(false)
        }
    }

    LaunchedEffect(camera, torchOn) {
        val bound = camera ?: return@LaunchedEffect
        if (!bound.cameraInfo.hasFlashUnit()) return@LaunchedEffect
        try {
            bound.cameraControl.enableTorch(torchOn)
        } catch (_: Throwable) {
            // The torch is a convenience; a device that refuses it must not break scanning.
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            imageAnalysis?.clearAnalyzer()
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Bridges the CameraX ListenableFuture to a coroutine; resumes on the main executor. */
private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                try {
                    continuation.resume(future.get())
                } catch (t: Throwable) {
                    continuation.resumeWithException(t)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/** Largest edge fed to ZXing from a picked photo; bigger images are scaled down first. */
private const val PHOTO_MAX_EDGE_PX = 2400

/**
 * Photo fallback (spec 3.3 / 3.4, "Choose from photos"): decodes a QR code from a still bitmap
 * via [RGBLuminanceSource]. Returns null when the image holds no readable QR code.
 *
 * The caller keeps ownership of [bitmap]; only copies made here are recycled here.
 */
fun decodeQrFromBitmap(bitmap: Bitmap): String? {
    val ownCopies = mutableListOf<Bitmap>()
    try {
        var source = bitmap
        if (source.config == Bitmap.Config.HARDWARE) {
            // A hardware bitmap has no readable pixels; getPixels would throw.
            source = source.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            ownCopies += source
        }
        val longestEdge = maxOf(source.width, source.height)
        if (longestEdge > PHOTO_MAX_EDGE_PX) {
            val scale = PHOTO_MAX_EDGE_PX.toDouble() / longestEdge
            val scaledWidth = (source.width * scale).toInt().coerceAtLeast(1)
            val scaledHeight = (source.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)
            if (scaled !== source) {
                source = scaled
                ownCopies += scaled
            }
        }
        val width = source.width
        val height = source.height
        if (width <= 0 || height <= 0) return null

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val luminance = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf<DecodeHintType, Any>(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true
        )
        return try {
            QRCodeReader().decode(BinaryBitmap(HybridBinarizer(luminance)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        }
    } catch (_: RuntimeException) {
        // Recycled bitmap or an unsupported config just means "no code in this picture".
        return null
    } catch (_: OutOfMemoryError) {
        // A picture too large to fit in memory is also just "no code in this picture".
        return null
    } finally {
        ownCopies.forEach { it.recycle() }
    }
}
