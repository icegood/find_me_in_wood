package com.icegood.findmeinwood.app

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * FR-2.6: [text] as a QR code [sizePx] square, black on white whatever the theme, with the
 * quiet border scanners need.
 */
fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val m = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val px = IntArray(m.width * m.height) { i ->
        if (m.get(i % m.width, i / m.width)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888)
}

/**
 * FR-2.6: a launcher for the camera scanner; [onResult] gets the invite read, or null when the
 * code was something else. A cancelled scan reports nothing.
 */
@Composable
fun rememberInviteScanner(onResult: (JoinInvite?) -> Unit): () -> Unit {
    val prompt = androidx.compose.ui.res.stringResource(R.string.scan_prompt)
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(ScanContract()) { r ->
        r.contents?.let { onResult(JoinInvite.parse(it)) }
    }
    return {
        launcher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(prompt)
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }
}
