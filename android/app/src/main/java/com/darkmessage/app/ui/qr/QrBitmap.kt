package com.darkmessage.app.ui.qr

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.darkmessage.app.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/*
 * QR rendering for the chat-key exchange (spec 1.3).
 *
 * Black on white regardless of the app theme, ECC level M, quiet zone of 4 modules,
 * nearest-neighbour scaling (ZXing scales the module matrix by an integer factor and
 * FilterQuality.None keeps any residual scaling hard-edged), on-screen edge >= 260 dp.
 *
 * Nothing here logs or persists the payload: the ImageBitmap lives in composition state only.
 */

/** Minimum on-screen edge of the QR code (spec 1.3: >= 260 dp). */
val QrMinSize: Dp = 260.dp

/** White card padding around the code; also the visual quiet zone (spec 1.3: >= 16 dp). */
private val QrCardPadding: Dp = 16.dp

/** Quiet zone that ZXing bakes into the matrix, in modules (spec 1.3: >= 4). */
private const val QR_MARGIN_MODULES = 4

private const val QR_BLACK = 0xFF000000.toInt()
private const val QR_WHITE = 0xFFFFFFFF.toInt()

/** Upper bound for the rendered bitmap edge, so a very high density cannot blow up memory. */
private const val QR_MAX_SIZE_PX = 1440

/**
 * Encodes [text] as a QR code bitmap of [sizePx] x [sizePx] pixels (clamped to a sane maximum).
 *
 * The largest payload this app ever produces is 339 ASCII chars (QR v14-M, 73x73 modules), which
 * always fits, so the `WriterException` that ZXing declares cannot happen for valid input; it is
 * left unchecked (Kotlin does not enforce it) rather than swallowed into a blank code.
 */
fun renderQr(text: String, sizePx: Int): ImageBitmap {
    require(text.isNotEmpty()) { "QR text must not be empty" }
    val edge = sizePx.coerceIn(1, QR_MAX_SIZE_PX)
    val hints = mapOf<EncodeHintType, Any>(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to QR_MARGIN_MODULES
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, edge, edge, hints)
    val width = matrix.width
    val height = matrix.height
    val pixels = IntArray(width * height)
    var rowOffset = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels[rowOffset + x] = if (matrix.get(x, y)) QR_BLACK else QR_WHITE
        }
        rowOffset += width
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

/**
 * The QR code on its white card, announced to TalkBack as "QR code with the encrypted chat key".
 */
@Composable
fun QrCodeImage(
    text: String,
    modifier: Modifier = Modifier,
    size: Dp = QrMinSize
) {
    val density = LocalDensity.current
    val sizePx = remember(size, density) { with(density) { size.roundToPx() } }
    val image = remember(text, sizePx) { renderQr(text, sizePx) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White)
            .padding(QrCardPadding)
    ) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.qr_a11y_qr_image),
            modifier = Modifier.size(size),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None
        )
    }
}
