package com.darkmessage.app.ui.qr

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Identical strings decoded again within this window are ignored (spec 3.4 / 5.2). */
private const val DEBOUNCE_MS = 2_000L

/**
 * CameraX analyzer that decodes QR codes from the luminance (Y) plane of YUV_420_888 frames
 * with ZXing core only - no ML Kit, no Google Play services (the app ships on RuStore and must
 * work on devices without GMS).
 *
 * Frames are never stored: the Y plane is copied into a reused scratch buffer, handed to ZXing
 * and forgotten. The decoded text is never logged - it is ciphertext of a chat key.
 *
 * [onCode] is always invoked on the main thread, at most once per 2 s for the same string.
 */
class ZxingQrAnalyzer(
    private val onCode: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = QRCodeReader()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hints = mapOf<DecodeHintType, Any>(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    // Touched only from the single analyzer executor thread, except the debounce pair which is
    // read/written under a lock because the callback hops to the main thread.
    private var luminance: ByteArray? = null
    private var rowBuffer: ByteArray? = null

    private val debounceLock = Any()
    private var lastText: String? = null
    private var lastTextAt = 0L

    override fun analyze(imageProxy: ImageProxy) {
        try {
            val text = decode(imageProxy)
            if (text.isNullOrEmpty()) return
            val now = SystemClock.elapsedRealtime()
            synchronized(debounceLock) {
                if (text == lastText && now - lastTextAt < DEBOUNCE_MS) return
                lastText = text
                lastTextAt = now
            }
            mainHandler.post { onCode(text) }
        } catch (_: RuntimeException) {
            // A malformed/closed frame must never crash the scanner; the next frame is tried.
        } finally {
            imageProxy.close()
        }
    }

    private fun decode(imageProxy: ImageProxy): String? {
        val source = luminanceSource(imageProxy) ?: return null
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        return try {
            reader.decode(bitmap, hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ChecksumException) {
            null
        } catch (_: FormatException) {
            null
        } finally {
            reader.reset()
        }
    }

    /** Copies the Y plane into a tightly packed [PlanarYUVLuminanceSource], honouring rowStride. */
    private fun luminanceSource(imageProxy: ImageProxy): PlanarYUVLuminanceSource? {
        val width = imageProxy.width
        val height = imageProxy.height
        if (width <= 0 || height <= 0) return null
        val plane = imageProxy.planes.firstOrNull() ?: return null
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null

        val buffer = plane.buffer
        buffer.rewind()

        val size = width * height
        val out = luminance?.takeIf { it.size == size } ?: ByteArray(size).also { luminance = it }

        if (pixelStride == 1 && rowStride == width) {
            if (buffer.remaining() < size) return null
            buffer.get(out, 0, size)
        } else {
            val neededPerRow = (width - 1) * pixelStride + 1
            if (rowStride < neededPerRow) return null
            val row = rowBuffer?.takeIf { it.size == rowStride }
                ?: ByteArray(rowStride).also { rowBuffer = it }
            var offset = 0
            for (y in 0 until height) {
                val toRead = minOf(rowStride, buffer.remaining())
                if (toRead < neededPerRow) return null
                buffer.get(row, 0, toRead)
                if (pixelStride == 1) {
                    row.copyInto(out, offset, 0, width)
                } else {
                    var src = 0
                    for (x in 0 until width) {
                        out[offset + x] = row[src]
                        src += pixelStride
                    }
                }
                offset += width
            }
        }
        return PlanarYUVLuminanceSource(out, width, height, 0, 0, width, height, false)
    }
}
