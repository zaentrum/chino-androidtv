package cloud.nalet.chino.tv.ui.auth

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Renders `text` to a [Bitmap] of `sizePx` square via ZXing's pure-Java
 * QRCodeWriter. ERROR_CORRECTION = M (~15%) is enough for a sharp screen
 * photo at 10 ft; the verification_uri_complete is typically <120 chars
 * so the resulting matrix is well under v10 and decodes reliably even
 * through phone-camera glare.
 */
fun encodeQrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 1,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val white = android.graphics.Color.WHITE
    val black = android.graphics.Color.BLACK
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix[x, y]) black else white)
        }
    }
    return bmp
}

@Composable
fun rememberQrImage(text: String, sizePx: Int): ImageBitmap =
    remember(text, sizePx) { encodeQrBitmap(text, sizePx).asImageBitmap() }
